using System.Collections.Concurrent;
using MongoDB.Bson;
using MongoDB.Bson.Serialization;
using MongoDB.Driver;
using Moq;

namespace SolarMicrogrid.Tests.TestHelpers;

/// <summary>
/// Provides an in-memory mock implementation of IMongoClient, IMongoDatabase, and IMongoCollection
/// that evaluates MongoDB FilterDefinitions and UpdateDefinitions in memory using BsonDocument rendering.
/// </summary>
public class InMemoryMongoStore
{
    private readonly ConcurrentDictionary<string, List<BsonDocument>> _collections = new();
    private readonly object _lock = new();

    public IMongoClient Client { get; }
    public IMongoDatabase Database { get; }

    public InMemoryMongoStore()
    {
        var mockClient = new Mock<IMongoClient>();
        var mockDb = new Mock<IMongoDatabase>();

        mockClient
            .Setup(c => c.GetDatabase(It.IsAny<string>(), It.IsAny<MongoDatabaseSettings>()))
            .Returns(mockDb.Object);

        mockDb
            .Setup(d => d.RunCommand(It.IsAny<Command<BsonDocument>>(), It.IsAny<ReadPreference>(), It.IsAny<CancellationToken>()))
            .Returns(new BsonDocument("ok", 1));

        mockDb
            .Setup(d => d.GetCollection<BsonDocument>(It.IsAny<string>(), It.IsAny<MongoCollectionSettings>()))
            .Returns((string name, MongoCollectionSettings _) => CreateCollectionMock<BsonDocument>(name));

        mockDb
            .Setup(d => d.GetCollection<SolarMicrogrid.Api.Models.User>(It.IsAny<string>(), It.IsAny<MongoCollectionSettings>()))
            .Returns((string name, MongoCollectionSettings _) => CreateCollectionMock<SolarMicrogrid.Api.Models.User>(name));

        mockDb
            .Setup(d => d.GetCollection<SolarMicrogrid.Api.Models.SolarStationInfo>(It.IsAny<string>(), It.IsAny<MongoCollectionSettings>()))
            .Returns((string name, MongoCollectionSettings _) => CreateCollectionMock<SolarMicrogrid.Api.Models.SolarStationInfo>(name));

        mockDb
            .Setup(d => d.GetCollection<SolarMicrogrid.Api.Models.EnergyReservation>(It.IsAny<string>(), It.IsAny<MongoCollectionSettings>()))
            .Returns((string name, MongoCollectionSettings _) => CreateCollectionMock<SolarMicrogrid.Api.Models.EnergyReservation>(name));

        mockDb
            .Setup(d => d.GetCollection<SolarMicrogrid.Api.Models.EnergyBookingSlot>(It.IsAny<string>(), It.IsAny<MongoCollectionSettings>()))
            .Returns((string name, MongoCollectionSettings _) => CreateCollectionMock<SolarMicrogrid.Api.Models.EnergyBookingSlot>(name));

        Client = mockClient.Object;
        Database = mockDb.Object;
    }

    public List<BsonDocument> GetRawCollection(string name)
    {
        return _collections.GetOrAdd(name, _ => new List<BsonDocument>());
    }

    public void Seed<T>(string collectionName, params T[] items)
    {
        var col = GetRawCollection(collectionName);
        lock (_lock)
        {
            foreach (var item in items)
            {
                var doc = item.ToBsonDocument();
                if (!doc.Contains("_id") || doc["_id"].IsBsonNull)
                {
                    doc["_id"] = ObjectId.GenerateNewId();
                }
                col.Add(doc);
            }
        }
    }

    private IMongoCollection<T> CreateCollectionMock<T>(string collectionName)
    {
        var mockCollection = new Mock<IMongoCollection<T>>();
        var mockIndexManager = new Mock<IMongoIndexManager<T>>();

        var emptyCursor = CreateCursor(new List<BsonDocument>());
        mockIndexManager
            .Setup(m => m.List(It.IsAny<CancellationToken>()))
            .Returns(emptyCursor);
        mockIndexManager
            .Setup(m => m.CreateOne(It.IsAny<CreateIndexModel<T>>(), It.IsAny<CreateOneIndexOptions>(), It.IsAny<CancellationToken>()))
            .Returns("ux_mock");

        mockCollection.SetupGet(c => c.Indexes).Returns(mockIndexManager.Object);

        // InsertOneAsync
        mockCollection
            .Setup(c => c.InsertOneAsync(It.IsAny<T>(), It.IsAny<InsertOneOptions>(), It.IsAny<CancellationToken>()))
            .Callback((T item, InsertOneOptions _, CancellationToken _) =>
            {
                lock (_lock)
                {
                    EnsureIdOnModel(item);
                    var doc = item.ToBsonDocument();
                    GetRawCollection(collectionName).Add(doc);
                }
            })
            .Returns(Task.CompletedTask);

        // InsertManyAsync
        mockCollection
            .Setup(c => c.InsertManyAsync(It.IsAny<IEnumerable<T>>(), It.IsAny<InsertManyOptions>(), It.IsAny<CancellationToken>()))
            .Callback((IEnumerable<T> items, InsertManyOptions _, CancellationToken _) =>
            {
                lock (_lock)
                {
                    var list = GetRawCollection(collectionName);
                    foreach (var item in items)
                    {
                        EnsureIdOnModel(item);
                        list.Add(item.ToBsonDocument());
                    }
                }
            })
            .Returns(Task.CompletedTask);

        // FindAsync
        mockCollection
            .Setup(c => c.FindAsync(
                It.IsAny<FilterDefinition<T>>(),
                It.IsAny<FindOptions<T, T>>(),
                It.IsAny<CancellationToken>()))
            .ReturnsAsync((FilterDefinition<T> filter, FindOptions<T, T> options, CancellationToken _) =>
            {
                List<T> results;
                lock (_lock)
                {
                    var matchingDocs = FilterDocuments(collectionName, filter);

                    if (options?.Sort != null)
                    {
                        var sortDoc = RenderSort(options.Sort);
                        matchingDocs = ApplySort(matchingDocs, sortDoc);
                    }

                    if (options?.Limit != null && options.Limit.Value > 0)
                    {
                        matchingDocs = matchingDocs.Take(options.Limit.Value).ToList();
                    }

                    results = matchingDocs.Select(DeserializeDoc<T>).ToList();
                }

                return CreateCursor(results);
            });

        // FindSync
        mockCollection
            .Setup(c => c.FindSync(
                It.IsAny<FilterDefinition<T>>(),
                It.IsAny<FindOptions<T, T>>(),
                It.IsAny<CancellationToken>()))
            .Returns((FilterDefinition<T> filter, FindOptions<T, T> options, CancellationToken _) =>
            {
                List<T> results;
                lock (_lock)
                {
                    var matchingDocs = FilterDocuments(collectionName, filter);
                    results = matchingDocs.Select(DeserializeDoc<T>).ToList();
                }

                return CreateCursor(results);
            });

        // CountDocumentsAsync
        mockCollection
            .Setup(c => c.CountDocumentsAsync(
                It.IsAny<FilterDefinition<T>>(),
                It.IsAny<CountOptions>(),
                It.IsAny<CancellationToken>()))
            .ReturnsAsync((FilterDefinition<T> filter, CountOptions _, CancellationToken _) =>
            {
                lock (_lock)
                {
                    return (long)FilterDocuments(collectionName, filter).Count;
                }
            });

        // UpdateOneAsync
        mockCollection
            .Setup(c => c.UpdateOneAsync(
                It.IsAny<FilterDefinition<T>>(),
                It.IsAny<UpdateDefinition<T>>(),
                It.IsAny<UpdateOptions>(),
                It.IsAny<CancellationToken>()))
            .ReturnsAsync((FilterDefinition<T> filter, UpdateDefinition<T> update, UpdateOptions _, CancellationToken _) =>
            {
                lock (_lock)
                {
                    var matchingDocs = FilterDocuments(collectionName, filter);
                    var target = matchingDocs.FirstOrDefault();
                    if (target == null)
                    {
                        return new UpdateResult.Acknowledged(0, 0, null);
                    }

                    ApplyUpdate(target, update);
                    return new UpdateResult.Acknowledged(1, 1, null);
                }
            });

        // UpdateMany
        mockCollection
            .Setup(c => c.UpdateMany(
                It.IsAny<FilterDefinition<T>>(),
                It.IsAny<UpdateDefinition<T>>(),
                It.IsAny<UpdateOptions>(),
                It.IsAny<CancellationToken>()))
            .Returns((FilterDefinition<T> filter, UpdateDefinition<T> update, UpdateOptions _, CancellationToken _) =>
            {
                lock (_lock)
                {
                    var matchingDocs = FilterDocuments(collectionName, filter);
                    foreach (var doc in matchingDocs)
                    {
                        ApplyUpdate(doc, update);
                    }
                    return new UpdateResult.Acknowledged(matchingDocs.Count, matchingDocs.Count, null);
                }
            });

        return mockCollection.Object;
    }

    private static void EnsureIdOnModel<T>(T item)
    {
        if (item == null || item is BsonDocument) return;
        var idProp = typeof(T).GetProperty("Id");
        if (idProp != null && idProp.PropertyType == typeof(string))
        {
            var currentId = idProp.GetValue(item) as string;
            if (string.IsNullOrEmpty(currentId))
            {
                idProp.SetValue(item, ObjectId.GenerateNewId().ToString());
            }
        }
    }

    private static T DeserializeDoc<T>(BsonDocument doc)
    {
        if (typeof(T) == typeof(BsonDocument))
        {
            return (T)(object)doc.DeepClone().AsBsonDocument;
        }
        return BsonSerializer.Deserialize<T>(doc.DeepClone().AsBsonDocument);
    }

    private List<BsonDocument> FilterDocuments<T>(string collectionName, FilterDefinition<T> filter)
    {
        var rawList = GetRawCollection(collectionName);
        var serializerRegistry = BsonSerializer.SerializerRegistry;
        var documentSerializer = serializerRegistry.GetSerializer<T>();
        var renderedFilter = filter.Render(new RenderArgs<T>(documentSerializer, serializerRegistry));

        return rawList.Where(doc => MatchesFilter(doc, renderedFilter)).ToList();
    }

    private static BsonDocument RenderSort<T>(SortDefinition<T> sort)
    {
        var serializerRegistry = BsonSerializer.SerializerRegistry;
        var documentSerializer = serializerRegistry.GetSerializer<T>();
        return sort.Render(new RenderArgs<T>(documentSerializer, serializerRegistry));
    }

    private static List<BsonDocument> ApplySort(List<BsonDocument> docs, BsonDocument sortSpec)
    {
        if (sortSpec.ElementCount == 0) return docs;
        var elem = sortSpec.GetElement(0);
        var field = elem.Name;
        var dir = elem.Value.ToInt32();

        return dir >= 0
            ? docs.OrderBy(d => d.Contains(field) ? d[field] : BsonNull.Value).ToList()
            : docs.OrderByDescending(d => d.Contains(field) ? d[field] : BsonNull.Value).ToList();
    }

    private static bool MatchesFilter(BsonDocument doc, BsonDocument filter)
    {
        foreach (var element in filter.Elements)
        {
            if (element.Name == "$and")
            {
                foreach (var subFilter in element.Value.AsBsonArray)
                {
                    if (!MatchesFilter(doc, subFilter.AsBsonDocument)) return false;
                }
            }
            else if (element.Name == "$or")
            {
                bool anyMatch = false;
                foreach (var subFilter in element.Value.AsBsonArray)
                {
                    if (MatchesFilter(doc, subFilter.AsBsonDocument))
                    {
                        anyMatch = true;
                        break;
                    }
                }
                if (!anyMatch) return false;
            }
            else
            {
                if (!MatchesFieldCondition(doc, element.Name, element.Value))
                {
                    return false;
                }
            }
        }
        return true;
    }

    private static bool MatchesFieldCondition(BsonDocument doc, string fieldName, BsonValue condition)
    {
        var hasField = doc.TryGetValue(fieldName, out var docValue);
        if (!hasField) docValue = BsonNull.Value;

        if (condition is BsonDocument condDoc && condDoc.Elements.Any(e => e.Name.StartsWith("$")))
        {
            foreach (var op in condDoc.Elements)
            {
                switch (op.Name)
                {
                    case "$eq":
                        if (!ValuesEqual(docValue, op.Value)) return false;
                        break;
                    case "$ne":
                        if (ValuesEqual(docValue, op.Value)) return false;
                        break;
                    case "$gt":
                        if (docValue == BsonNull.Value || CompareBsonValues(docValue, op.Value) <= 0) return false;
                        break;
                    case "$gte":
                        if (docValue == BsonNull.Value || CompareBsonValues(docValue, op.Value) < 0) return false;
                        break;
                    case "$lt":
                        if (docValue == BsonNull.Value || CompareBsonValues(docValue, op.Value) >= 0) return false;
                        break;
                    case "$lte":
                        if (docValue == BsonNull.Value || CompareBsonValues(docValue, op.Value) > 0) return false;
                        break;
                    case "$in":
                        if (!op.Value.AsBsonArray.Any(v => ValuesEqual(docValue, v))) return false;
                        break;
                    case "$exists":
                        bool shouldExist = op.Value.ToBoolean();
                        if (shouldExist != hasField) return false;
                        break;
                    case "$type":
                        break;
                }
            }
            return true;
        }

        return ValuesEqual(docValue, condition);
    }

    private static bool ValuesEqual(BsonValue a, BsonValue b)
    {
        if ((a == null || a.IsBsonNull) && (b == null || b.IsBsonNull)) return true;
        if (a == null || b == null || a.IsBsonNull || b.IsBsonNull) return false;
        if (a.Equals(b)) return true;
        // Handle ObjectId vs String comparison seamlessly
        if ((a.IsObjectId && b.IsString) || (a.IsString && b.IsObjectId))
        {
            return a.ToString() == b.ToString();
        }
        return a.ToString() == b.ToString();
    }

    private static int CompareBsonValues(BsonValue a, BsonValue b)
    {
        if (a.IsValidDateTime && b.IsValidDateTime)
        {
            return a.ToUniversalTime().CompareTo(b.ToUniversalTime());
        }
        if (a.IsNumeric && b.IsNumeric)
        {
            return a.ToDouble().CompareTo(b.ToDouble());
        }
        return a.CompareTo(b);
    }

    private static void ApplyUpdate<T>(BsonDocument doc, UpdateDefinition<T> update)
    {
        var serializerRegistry = BsonSerializer.SerializerRegistry;
        var documentSerializer = serializerRegistry.GetSerializer<T>();
        var rendered = update.Render(new RenderArgs<T>(documentSerializer, serializerRegistry)).AsBsonDocument;

        foreach (var op in rendered.Elements)
        {
            switch (op.Name)
            {
                case "$set":
                    foreach (var field in op.Value.AsBsonDocument.Elements)
                    {
                        doc[field.Name] = field.Value;
                    }
                    break;
                case "$unset":
                    foreach (var field in op.Value.AsBsonDocument.Elements)
                    {
                        doc.Remove(field.Name);
                    }
                    break;
                case "$addToSet":
                    foreach (var field in op.Value.AsBsonDocument.Elements)
                    {
                        if (!doc.Contains(field.Name) || !doc[field.Name].IsBsonArray)
                        {
                            doc[field.Name] = new BsonArray();
                        }
                        var arr = doc[field.Name].AsBsonArray;
                        if (!arr.Contains(field.Value))
                        {
                            arr.Add(field.Value);
                        }
                    }
                    break;
                case "$pull":
                    foreach (var field in op.Value.AsBsonDocument.Elements)
                    {
                        if (doc.Contains(field.Name) && doc[field.Name].IsBsonArray)
                        {
                            var arr = doc[field.Name].AsBsonArray;
                            for (int i = arr.Count - 1; i >= 0; i--)
                            {
                                if (ValuesEqual(arr[i], field.Value))
                                {
                                    arr.RemoveAt(i);
                                }
                            }
                        }
                    }
                    break;
            }
        }
    }

    public static IAsyncCursor<T> CreateCursor<T>(IEnumerable<T> items)
    {
        var list = items.ToList();
        var cursorMock = new Mock<IAsyncCursor<T>>();
        bool moved = false;

        cursorMock.SetupGet(c => c.Current).Returns(() => list);
        cursorMock
            .Setup(c => c.MoveNext(It.IsAny<CancellationToken>()))
            .Returns(() =>
            {
                if (!moved)
                {
                    moved = true;
                    return true;
                }
                return false;
            });
        cursorMock
            .Setup(c => c.MoveNextAsync(It.IsAny<CancellationToken>()))
            .ReturnsAsync(() =>
            {
                if (!moved)
                {
                    moved = true;
                    return true;
                }
                return false;
            });

        return cursorMock.Object;
    }
}
